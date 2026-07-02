# July 2026 — H-Series Opaque-Type Detail (IP-12, IP-13, IP-09b, IP-SA)

Permanent detail record for four already-closed items whose original prompts carried context
not reproduced in `INDEX.md`'s Cross-Cutting Entries table. Migrated from
`.claude/progress-tracking/working-docs/JULY_SPRINT_PROMPTS.md` and `july-follow-ups.md` —
instructional "you are mithril, do X" framing condensed (its value was procedural and is
already spent); reviewer verdict/rationale text preserved verbatim where it existed, since
that's the hard-to-reconstruct audit trail.

---

## IP-12 — ChainId Leakage Propagation (H9)

**Status:** ✅ CLOSED, commit `8c382316d`. **Complexity:** XS (type already existed, 11 leakage sites).

**Scope:** `domain/Transaction.scala` (8 sites, all tx case classes), `utils/BlockchainConfig.scala`
(1), `jsonrpc/EthInfoService.scala` (1), `domain/SignedTransaction.scala` (1).

**Gate:** FORGE+BEACON — `Transaction.scala`/`SignedTransaction.scala` (EIP-155 chainId in
signing/replay protection), `BlockchainConfig.scala` (chain config parsing). Opaque type
`ChainId = BigInt` erases at runtime, so changes were expected byte-identical — gate confirmed
EIP-155 replay-protection logic intact. **IP-12-FG: APPROVED.**

**Constraint honored:** HOCON chain config files (`mordor-chain.conf`, `sepolia-chain.conf`) —
raw numeric chain IDs there are config values, not Scala leakage sites, correctly left as-is.

---

## IP-13 — BlockNumber Leakage Propagation (H7, 95 sites, 18 SNAP)

**Status:** ✅ CLOSED, commits `a007ef951` (consensus/ledger/vm + cascade), `e14231b07` (SNAP
sync layer). **Complexity:** L — largest single H-series pass.

**Historical context that mattered:** PR #1384 (a prior stale-base regression) was caused by a
BlockNumber sweep that missed SNAP layer files. This pass explicitly required ALL SNAP files
(`SNAPSyncController.scala` — 18 sites — plus `TrieNodeHealingCoordinator`,
`StorageRangeCoordinator`, `AccountRangeCoordinator`, and worker files) to land in the **same
commit** as a hard constraint — the discipline every subsequent SNAP-touching prompt in this
sprint (`IP-CL-J` Batch B, `IP-CL-DE`) explicitly carries forward.

**Non-SNAP scope (42 known sites, 8 files):** `db/storage/ReferenceCountNodeStorage.scala` (12),
`ledger/BlockRewardCalculator.scala` (6), `consensus/pow/validators/StdOmmersValidator.scala` (5),
`consensus/blocks/BlockGeneratorSkeleton.scala` (5), `blockchain/sync/SyncProtocol.scala` (4),
`blockchain/checkpoint/CheckpointExporter.scala` (4), `vm/EvmConfig.scala` (3),
`domain/BlockchainReader.scala` (3) — plus ~35 more found by grep.

### IP-13-FG — Forge/Beacon Gate Verdict (verbatim, from `july-follow-ups.md`)

The four forge-gated files were reviewed for consensus impact and converted to `BlockNumber`.
All conversions are byte-for-byte consensus-neutral — the opaque wrap/unwrap boundary is placed
at every integer-arithmetic and BigInt-config comparison point via `.value`, so era math, uncle
depth checks, gas-limit convergence, and fork selection produce identical results.

- **BlockRewardCalculator.scala** — 6 method params → `BlockNumber`. Era arithmetic
  (`eraNumber`: `(blockNumber.value - 1) / eraDuration`; ommer inclusion:
  `firstEraOmmerMiningRewardMaxNumer - (blockNumber.value - ommerNumber.value - 1)`) and
  fork-reward comparisons (`newBlockReward`: `blockNumber.value >= constantinople/byzantium`)
  use `.value` at the BigInt boundary. ECIP-1017 emission schedule unchanged. **forge: APPROVED.**
- **StdOmmersValidator.scala** — 5 params → `BlockNumber` (+ `OmmersValidator` trait's two
  `validate` overloads). Depth clamp `blockNumber.value.min(OmmerGenerationLimit).toInt`
  preserves the exact `BigInt.min(Int)` semantics. Boolean validation outcomes unchanged.
  **forge: APPROVED.**
- **BlockGeneratorSkeleton.scala** — `defaultPrepareHeader`/`prepareHeader`(abstract)/
  `prepareBlock`/`prepareTransactions`/`calculateGasLimit` params → `BlockNumber`. PoW-only
  file, no ETH `forTimestamp` path — no beacon gate needed. `.value` used at
  `difficultyCalc.calculateDifficulty`, `getExtraData`, `gasLimitAdjustmentStartAt`, and the
  `olympiaBlockNumber` comparison. `number = blockNumber` replaces
  `number = BlockNumber(blockNumber)` (same value). **forge: APPROVED.**
- **EvmConfig.scala** — all three `forBlock()` overloads' `blockNumber` param → `BlockNumber`.
  Block-number → fork-config dispatch (`.filterNot { number > blockNumber.value }`) activates at
  identical block heights. The `forTimestamp`-style timestamp override inside the 2nd overload
  (Shanghai/Cancun/Prague/Osaka) was NOT touched — beacon scope. **forge APPROVED** for the
  `forBlock()` ETC path; **beacon APPROVED** for the `forTimestamp()` ETH timestamp-override path
  — no changes required. The override's `blockNumber` param is already `BlockNumber` and flows
  to the ETC `forBlock` overload; the four `is{Shanghai,Cancun,Prague,Osaka}Timestamp(timestamp)`
  gates take `Timestamp`-only (no `blockNumber: BigInt`); `OsakaOpCodes` selection unchanged.
  Activation timestamps byte-identical.

**Direct caller updates (same forge gate, needed to keep `sbt compile` green):**
`BlockPreparator.scala` (reward + 5 forBlock sites), `ValidatorsExecutor.scala`,
`PoWBlockGenerator.scala`, `NoOmmersBlockGenerator.scala`, `RestrictedPoWBlockGeneratorImpl.scala`,
`StxLedger.scala` (4), `BlockExecution.scala` (2), `TestModeBlockExecution.scala`,
`EthSimulateService.scala`, `EthTxService.scala`, `SignedTransaction.scala` (2),
`StdSignedTransactionValidator.scala` (2 — `blockHeaderNumber: BigInt` param kept as-is bridge,
wrapped `BlockNumber(...)` at call site; changing that param type cascades into tx-validation
callers, out of this gate's scope).

**Verification:** `sbt compile` (main sources) → 0 errors. `sbt scalafmtAll` → clean.
Test-source cascade (~20 files) deferred to IP-14 per the batch-1 test-source-fix plan.

---

## IP-09b — GasAmount EVM Pipeline Sweep (H5b / DEF-02)

**Status:** ✅ CLOSED, commit `8bbdc84b0`. **Complexity:** M — vm/ internal pipeline, no SNAP,
forge+beacon gate.

**Why it existed:** IP-09 (H5, 31+ call sites) correctly excluded the EVM internal computation
pipeline because converting `CallTracer`/`ExecutionTracer` requires first converting the
upstream `ProgramContext.startGas` register and `ProgramResult.gasUsed` — the whole pipeline
had to move together. IP-09b completed that sweep: `ProgramContext.startGas` (root register) →
`ProgramResult.gasUsed`/`ProgramState` → `ExecEnv` → tracer hook signatures (`CallTracer`,
`ExecutionTracer`, `VmTracer`, `PrestateTracer`, `StructLogTracer`, `InternalTransaction`) →
`OpCode.scala`/`PrecompiledContracts.scala` gas sites → caller cascade
(`BlockPreparator`/`StxLedger`/`BlockExecution`). Static opcode gas-cost table constants
(`G_verylow`, `G_sha3`, etc.) stay `BigInt` — spec constants, not domain values.

### IP-09b-FG — Forge/Beacon Gate Verdict (verbatim, from `july-follow-ups.md`)

**forge: APPROVED** (2026-07-02) — byte-for-byte consensus-neutral. The opaque wrap/unwrap
boundary is placed at every BigInt-arithmetic, fee-schedule-comparison, `.toUInt256`, and
wei-multiplication point; no gas value is altered at any EVM execution or ledger boundary.
Per key site:
- **OpCode.execute OutOfGas (241)** — `calcGas` still returns `BigInt` (base+var from fee
  schedule); `gas > state.gas.value` unwraps for the raw comparison; `spendGas(gas: BigInt)`
  wraps internally. `gas = GasAmount.Zero` on OOG is consistent with `toResult`'s `useWholeGas`
  path (gasRemaining→Zero). Identical.
- **CreateOp OutOfGas (890)** — same pattern; `opcodeGasCost = GasAmount(gas)` then
  `spendGas(gas)`; `availableGas = state.gas - state.opcodeGasCost` (GasAmount) and
  `GasAmount(state.config.gasCap(availableGas.value))` unwrap→cap→wrap. Identical.
- **GAS opcode (750)** — `(state.gas.value - feeSchedule.G_base).toUInt256`: `.value` unwrap
  before BigInt subtraction of its own base cost, then `.toUInt256`. Reports post-cost gas
  remaining exactly as before.
- **SSTORE EIP-2200 stipend (695)** — `state.gas.value <= feeSchedule.G_callstipend`: raw
  BigInt comparison against the (unwrapped) current gas; `G_callstipend` stays a fee-schedule
  `BigInt`. Stipend guard byte-identical.
- **gasCap helper (1184-1185)** — `state.gas.value >= consumedGas` and
  `g.min(config.gasCap(state.gas.value - consumedGas))`: both `.value` unwraps at the BigInt
  cap arithmetic (EIP-150 63/64 forwarding). Identical.
- **CallOp gas accounting (1027/1071-1074/1104)** — `startGas = GasAmount(calcStartGas(...))`;
  refund-credit via `spendGas(-result.gasRemaining.value)` / `-startGas.value` preserves the
  negative-BigInt "credit unused gas back" semantics exactly.
- **Precompiles (PrecompiledContracts 158-165, ModExp 278-283, EIP-7823 260-271)** —
  `g <= context.startGas.value`, `context.startGas - GasAmount(g)`, `GasAmount.Zero` on
  OOG/fail; all `gas()` results remain spec `BigInt`. Identical.
- **calcTotalGasToRefund (264-279)** — YP eq.72 / EIP-3529: `GasAmount(gasLimit.value)` is an
  identity round-trip (Transaction.gasLimit already GasAmount); `gasUsed / maxRefundQuotient`
  (GasAmount/Int), `.min(gasRefund)`, `gasRemaining + …` all GasAmount ops. Refund cap unchanged.
- **executeTransaction gasUsed boundary (408/432/434/440/451/498)** —
  `executionGasBase = gasLimit - totalGasToRefundBase`; EIP-7623
  `.max(GasAmount(calcFloorDataGas))`; wei refund/miner via `.value * gasPrice`;
  `TxResult(..., executionGasToPayToMiner.value, ...)`. The consensus gasUsed is byte-identical.
- **ECIP-1017 reward + ECIP-1111 treasury (55-113)** — block rewards are wei (unchanged, not
  GasAmount); treasury credit `baseFee * blockHeader.gasUsed.value` unwraps the header GasAmount
  for the wei product; `blockHeader.gasUsed > GasAmount.Zero` guard preserved. gasUsed boundary
  intact.
- **Receipt accumulation (StxLedger 66/108, BlockPreparator 572/590/527, BlockExecution 62)** —
  `cumulativeGasUsed = GasAmount(acumGas + gasUsed)` then `.value` back into the
  `acumGas: BigInt` accumulator is an exact round-trip; block-level `GasAmount(result.gasUsed)`
  wrap feeds `validateBlockAfterExecution` at the correct boundary. Receipts-trie/RLP bytes
  unchanged.
- **Tracer hooks** — all `gas`/`gasUsed` params are `GasAmount` and align with the GasAmount
  arguments passed from `VM.call/create` and `StxLedger`. Trace paths are non-consensus (debug
  output only) and produce identical values via `.value` at JSON encode.
- **Fee-schedule constants** — confirmed `G_*`/`feeSchedule.*` remain `BigInt`. No spec
  constant was wrapped.
- **ETH withdrawal-queue system call (BlockExecution 390, `startGas = GasAmount(30000000)`)** —
  trivial literal wrap on the post-merge ETH path; flagged for beacon but consensus-neutral.

**beacon: APPROVED** (2026-07-02) — byte-for-byte consensus-neutral on the ETH/Sepolia
(post-merge, Osaka) path. Per key ETH site:
- **EIP-150 gasCap 63/64 (OpCode 1184-1185)** — feeds `EvmConfig.gasCap(g: BigInt): BigInt =
  subGasCapDivisor.map(d => g - g/d)` — byte-identical to go-ethereum's `callGas` (63/64 rule).
- **GAS opcode (750)** — remaining gas pushed to the stack identical to geth's `opGas`
  (post-cost value).
- **EIP-2200 SSTORE stipend (695)** — forces the `G_callstipend + 1` OOG charge exactly as
  before.
- **EIP-7002/7251 system call (BlockExecution 390)** — 30M confirmed against local spec
  `EIPs/EIPS/eip-7002.md:156` and `eip-7251.md:153` ("dedicated gas limit of `30_000_000`").
  Applied identically to both `WithdrawalQueueAddress` and `ConsolidationQueueAddress` in
  `processPragueSystemCalls`.
- **EIP-4844 KZG point-evaluation precompile** — `KZG_GAS = BigInt(50000)` (spec constant, left
  unwrapped); blob precompile gas correctly typed at its boundary, 50000-gas cost unchanged.
- **Tracer hooks** — non-consensus (debug output) and numerically identical.
- **CREATE code-deposit (VM.scala 299-317, EIP-170/3541)** — EIP-3541 `0xEF` reject and
  max-code-size checks unchanged. Post-Cancun/Prague/Osaka deposit accounting byte-identical.
- **Pipeline roots** — `ProgramContext.startGas: GasAmount` = `GasAmount(tx.gasLimit.value -
  intrinsicGas)` (identity over the already-GasAmount `tx.gasLimit`); `spendGas`/`refundGas`
  preserve the negative-BigInt "credit unused gas back" semantics; `toResult` OOG→
  `GasAmount.Zero` matches prior `useWholeGas`.
- **Fee-schedule constants** — grep-confirmed no `GasAmount(...)` wrap on any `G_*`/
  `feeSchedule.*` field.

**Compile:** `sbt compile-all` → 0 main-source errors (test-source errors pre-date IP-09b,
deferred to IP-14). **Format:** clean.

---

## IP-SA — Batch 1 Full Opaque Type Straggler Audit

**Status:** ✅ CLOSED (audit-only, no code edits). **Output:**
`.local/docs/research-july/straggler-audit-results.md`.

Systematic audit across all 19 domain opaque types (12 BigInt-based, 1 Long-based, 6
ByteString-based) run before IP-14, because IP-10c's straggler grep found 5× more sites than
its known list, and a post-sprint spot-check on Nonce/Wei/GasAmount found 50+ additional sites.
This audit is what produced the entire `IP-CL-*` cleanup series (see `INDEX.md`'s Cross-Cutting
Entries and `.claude/sprints/QUEUE.md`'s Findings Resolution Log) — every `IP-CL-A` through
`IP-CL-I` prompt traces back to a classification (LEAKAGE / BORDERLINE / ACCEPTABLE) this audit
produced, run in two rounds (round 1 + round 2, both 2026-07-02).

---

## IP-CL-A — see `INDEX.md` Cross-Cutting Entries

IP-CL-A's (JSON-RPC/testmode/faucet/CLI DTO sweep) full outcome — all 4 batch commits, the
GraphQLSchema/TestService/AdminService cascade-caller findings, and the confirmed-out-of-scope
sites — is already recorded in `.claude/sprints/log/INDEX.md`'s Cross-Cutting Entries table; not
duplicated here.
