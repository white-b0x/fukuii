# `modules/execution` — L4 subsystem breadcrumb

_The single-block execution pipeline. Depends **down-only** on `evm`, `trie`, `storage`, `domain` — an
upward `.dependsOn` or a `consensus` import is a compile error (grep-clean; the structural fix for the
pre-rebuild `ledger ↔ consensus` cycle). Full record:
[`docs/architecture/fukuii-rebuild/implementation-reports/05-L4-execution.md`](../../docs/architecture/fukuii-rebuild/implementation-reports/05-L4-execution.md);
plan: [`plan/L4.md`](../../docs/architecture/fukuii-rebuild/plan/L4.md); byte-cited RX evidence:
[`plan/rx/L4.md`](../../docs/architecture/fukuii-rebuild/plan/rx/L4.md). Read the record before
structural changes here._

## What lives here

The per-block apply pipeline: pre-execution system calls (EIP-4788/2935, `PreExecutionProcessor`) →
the per-transaction engine (`TransactionProcessor`) → withdrawals (EIP-4895,
`WithdrawalsProcessor`) → post-execution requests (EIP-6110/7002/7251, `RequestProcessors` /
`DepositRequestProcessor` / `SystemCallRequestProcessor`) → the reward/finalize seam (`RewardScheme`,
ECIP-1017 era emission) → state commitment. The immutable per-fork `ProtocolSpec` bundle
(`ProtocolSpec.scala`, `ProtocolSchedule.scala`) wraps L3's single `EvmConfig.forBlock(header,
schedule)` resolution. Base-fee disposition (`CalcBaseFee`, EIP-1559 burn / ECIP-1111 treasury+floor)
and blob fee (`CalcBlobFee`). The concrete `WorldState` (`InMemoryWorldState`,
`InMemoryAccountStorage`, `StateMpt`, `CodeStorage`) realizing L3's abstract seams over L2's trie. The
block loop itself (`BlockProcessor.processBlock` / `processBlockWithOutcome`) and the per-block
outcome (`BlockExecutionOutcome`, `BlockStateDiff`, `MutationReason`, `MutationSink`). The
`ethereum/tests` BlockchainTest harness (`src/test/.../blockchaintest/`) — both fork schedules, ETH
and ETC.

## Invariants (do not break)

- **All reward/economics and state-commitment code here is byte-exact / consensus-critical.** Route
  through the Consensus-Critical Change Protocol: **forge** (ECIP-1017 emission, ECIP-1111 base-fee
  floor/treasury) + **beacon** (EIP-1559 burn, withdrawals, EIP-7685 requests) co-sign against the
  **REFERENCE CLIENTS**, byte-cited — never against `fukuii/july-fourth` or fukuii's own sets (a
  self-reference; see `.agents/protocols/consensus/reference-client-authority.md`). **banksy is a
  required consult on ECIP-1111** (the security-budget economics ECIP-1122's tip floor is sized
  against) — never a sole reviewer.
- **`execution` has no edge to `consensus`.** An upward `import consensus.*` is a compile error — do
  not reintroduce it to "reach" the engine. MESS/`BranchResolution` lives at L5, not here.
- **The reward lives on ONE seam, no `if(isPoW)` in the apply loop.** `RewardScheme` selection fails
  LOUD (`sys.error`) on an unresolved/mismatched scheme — never a quiet zero-reward. The reward reads
  `header.isPoS` **per block** (the merge-correctness guarantee across cross-boundary reorgs).
- **ECIP-1017 era math is core-geth's exact integer form — never a `BigDecimal` reconstruction.** Era
  index = integer division `(blockNumber-1)/eraLength`; separate integer `4^era` and `5^era` before
  multiply-divide; Era-0-vs-Era-≥1 uncle/nephew formula switch. **core-geth is the SOLE authority;
  besu-etc is a second independent JVM cross-check** — both must agree.
- **The `ProtocolSpec` bundle is resolved ONCE per header and wraps L3's single `forBlock`** — never
  re-derive the fork mid-execution; the bundle is immutable and per-instance-threaded (R2: no
  `object … { var … }` process-global execution state).
- **Withdrawals and EIP-7685 requests are hard-rejected on ETC** — the `RequestType` map degrades to
  `noOp` on the PoW path; never silently apply a post-Merge request call on ETC.
- **Block+weight write is atomic** — the primitive lives at L2 (`storage.BlockStore.putBlock`, one
  `WriteBatch`), not here (relocated per F-L4-6; do not resurrect a provisional `ChainWeight`/
  `AtomicBlockWriter` in this module).
- **`SimulationOptions` is immutable and per-call** — never a mutable instance/`@volatile` flag
  threaded into the VM run.
- **L4 owns the per-block state-diff, not the reorg segmentation.** `BlockExecutionOutcome` +
  `MutationReason` are L4 types; the reorg-aware `ChainNotification` wire ADT is L5's — do not grow a
  reorg-decision path here.

## Gotchas

- `ProtocolSpec` is scoped to fukuii's actually-varying collaborators (EvmConfig, reward scheme,
  system-call processors, validators, fee/base-fee handling) — do not mirror besu's full ~30-field
  bundle; most of the extra fields (BAL, blob-gas, EIP-7778 dual-gas) are ETH-future.
- The post-execution phase order is a deliberate pick of besu's `withdrawals → requests → reward`
  sequence (go-ethereum instead runs requests in `PostExecution` then withdrawals+reward in
  `Engine.Finalize` — the two references genuinely differ in internal order; both are
  state-root-commutative for the current two families, but the pick here is besu's).
- The ECIP-1111 lump-sum base-fee credit (`block.gasUsed * block.baseFee`) is computed **once at
  finalize**, never per-tx against a varying value — it equals the per-tx sum only because `baseFee`
  is block-constant.
- Four real consensus bugs were caught here by validating against the reference clients + the
  `ethereum/tests` corpus (not `july-fourth`): EIP-7702 warm-set ordering, a dormant EIP-7825 gas cap
  on ETH Osaka, PREVRANDAO not threaded post-Merge, and a harness chain-id gap masking an ETC
  sender-recovery risk — see the record §3 before assuming a similarly-shaped path is already correct.
