# Sprint Log — Master Index

Permanent, sprint-agnostic record of what changed in this codebase, why, and which sprint
batch did it. Every sprint from now on — modernization, performance, security, features,
audits — logs here, not into a sprint-specific directory name.

A batch is not eligible for `sprint-archive.sh` until it has an entry here (see
`.claude/agent-protocols/sprint-lifecycle.md`).

**Detail files in this directory:**
- [`legacy-modernization-log/`](legacy-modernization-log/INDEX.md) — the June 2026
  Pekko/Scala3 modernization effort's full historical record (31 files), migrated verbatim
  from its original home at `.claude/agent-protocols/modernization-log/` (Phase B of the
  progress-tracking/modernization-log retirement will remove the original once this copy is
  confirmed). Left as archived detail, not actively added to going forward — new entries land
  in this `INDEX.md` and its Cross-Cutting Entries table instead.
- [`july-2026-h-series-detail.md`](july-2026-h-series-detail.md) — condensed context + full
  verbatim forge/beacon verdict text for `IP-12`, `IP-13` (+`IP-13-FG`), `IP-09b` (+`IP-09b-FG`),
  and `IP-SA` — detail that only existed in `JULY_SPRINT_PROMPTS.md`/`july-follow-ups.md`
  before this migration.

---

## Area Status

| Area | Last touched | Status | Notes |
|------|--------------|--------|-------|
| core | 2026-07-01 | 19 domain opaque types, H-series (Nonce/Wei/GasAmount/GasPrice/EIP-1559/ChainId/BlockNumber/Timestamp) fully propagated across src/main/ | See Cross-Cutting Entries below |
| consensus | 2026-07-01 | Reward calc (ECIP-1017), Ethash, Engine API, EVM pipeline all opaque-typed for the H-series | See Cross-Cutting Entries below |
| sync | 2026-07-01 | SNAP layer (SNAPSyncController + coordinators) BlockNumber-propagated in one commit (PR #1384 regression-pattern avoided) | See Cross-Cutting Entries below |
| api | 2026-07-02 | jsonrpc/testmode/faucet/CLI DTO layer opaque-type sweep — **CLOSED**, all 4 sub-batches landed | See Cross-Cutting Entries below |

Add a row (or a new `<area>.md` for detail, linked from this table) the first time a batch
touches that area. Keep the same area names `modernization-log/` already uses where they
overlap (`api`, `consensus`, `core`, `ext`, `network`, `node`, `storage`, `sync`) so the two
logs stay easy to cross-reference.

---

## Cross-Cutting Entries (3+ areas, or notable for any other reason)

| Batch | What | Areas | Commits |
|-------|------|-------|---------|
| July sprint, Batch 1 (H-series) | **Nonce** opaque type — created + propagated to 52 sites | core, api, consensus | `f30bcde3f` |
| July sprint, Batch 1 (H-series) | **Wei** opaque type — created + propagated to 47 sites, `BlockRewardCalculator` return boundaries wrapped | core, consensus, ledger | `e18ddd188`, `418106877` |
| July sprint, Batch 1 (H-series) | **GasAmount** opaque type — propagated to 31+ call sites (IP-09), then the full EVM internal pipeline (`ProgramContext.startGas` root register → `ProgramResult.gasUsed` → tracer hook signatures) (IP-09b) | core, consensus, vm, ledger | `75b290bbf`, `8bbdc84b0` |
| July sprint, Batch 1 (H-series) | **GasPrice** — 5 jsonrpc leakage sites fixed, absorbed into the EIP-1559 cascade commit rather than a standalone commit | api | `8418c42e1` |
| July sprint, Batch 1 (H-series) | **BaseFeePerGas / MaxFeePerGas / PriorityFeePerGas** (EIP-1559) — 3 new opaque types created, propagated through `BlockHeader`/`Transaction` (domain), then consensus/network/ledger, then jsonrpc, then a 65-error/16-file cascade fix, then Pickler instances | core, consensus, network, ledger, api, storage | `0f5ccd251`, `0e478c301`, `5a6c09ecd`, `8418c42e1`, `837a1ac36` |
| July sprint, Batch 1 (H-series) | **Timestamp** — audited, 0 genuine leakage sites found (existing raw uses are wall-clock `Ms:` fields or Engine API wire boundaries, correctly left as-is) | core | _(no commit — audit-only closure)_ |
| July sprint, Batch 1 (H-series) | **ChainId** — propagated through EIP-155 signing path + cascade (`Transaction`, `SignedTransaction`, `BlockchainConfig`) | core, api, network | `8c382316d` |
| July sprint, Batch 1 (H-series) | **BlockNumber** — propagated through consensus/ledger/vm, then the SNAP sync layer as a single commit (all-SNAP-files-together discipline, per the PR #1384 regression precedent) | core, consensus, ledger, vm, sync | `a007ef951`, `e14231b07` |
| July sprint, Batch 1 (IP-CL-A) | **JSON-RPC/testmode/faucet/CLI DTO sweep** — the straggler audit found this layer never got fully swept during H5–H9: DTOs had some fields already typed (e.g. `TransactionResponse.gasPrice: GasPrice`) but siblings (`nonce`, `value`, `gas`) stayed raw `BigInt` (Rule 2 half-typed pattern). 4 batches, 28 files, ~70 sites, no forge/beacon gate (pure response/request DTOs + dev tooling, no consensus computation). Also corrected round 1's wrong "GasPrice — CLEAN (main)" verdict (`TransactionRequest.scala`, `EthSimulateService.scala`) and fixed a missed IP-12 site (`EthInfoService.ChainIdResponse`) found mid-sweep. `sbt compile-all`: 0 main-source errors throughout all 4 batches; 453 pre-existing test-source errors unchanged, explicitly deferred to IP-14 (which runs after every IP-CL-* prompt). Confirmed correctly OUT of scope during the sweep (different type space, not leakage): `EthUserService.GetTransactionCountResponse` (Account.nonce is UInt256, not the tx-pool Nonce type), `EthProofService.StorageProof.value` ×4 (storage proof values have no domain opaque type), `JsonMethodsImplicits.scala:69 getDuration` (generic JSON utility, unrelated to domain types). | api, core | `34a933ccc` (batch 1: TransactionResponse/Request, TransactionReceiptResponse, BlockResponse), `8f474d800` (batch 2: Eth* services, GraphQL, testmode wrapper + ChainIdResponse fix), `1a984d40f` (batch 3: TestService, testmode consensus/mining chain), `cf1cbed07` (batch 4: faucet, CLI, transaction history, DebugTrace) |

**Note on PR #1384:** the SNAP-layer BlockNumber commit (`e14231b07`) and every subsequent
SNAP-touching prompt in this sprint (`IP-CL-J` Batch B, `IP-CL-DE`) explicitly carries forward
the "all SNAP files land in one commit" discipline established here, because a prior partial
SNAP sweep caused a stale-base regression (PR #1384). Any future SNAP-layer opaque-type or
type-safety work should follow the same rule.
